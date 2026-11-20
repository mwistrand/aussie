package aussie.core.model.ratelimit;

import java.time.Instant;

/**
 * Token bucket rate limiting algorithm implementation.
 *
 * <p>The token bucket algorithm maintains a bucket of tokens that refills at a
 * steady rate. Each request consumes one token. Requests are rejected when the
 * bucket is empty.
 *
 * <p>Key characteristics:
 * <ul>
 *   <li>Allows controlled bursts up to bucket capacity</li>
 *   <li>Smooth refill rate prevents boundary issues</li>
 *   <li>Good for general-purpose rate limiting</li>
 * </ul>
 *
 * <p>Configuration mapping:
 * <ul>
 *   <li>{@code requestsPerWindow} → refill amount per window</li>
 *   <li>{@code windowSeconds} → refill period</li>
 *   <li>{@code burstCapacity} → maximum bucket capacity</li>
 * </ul>
 */
public final class BucketAlgorithm {

    private static final BucketAlgorithm INSTANCE = new BucketAlgorithm();

    private BucketAlgorithm() {}

    /**
     * Return the singleton instance.
     *
     * @return the bucket algorithm instance
     */
    public static BucketAlgorithm getInstance() {
        return INSTANCE;
    }

    public RateLimitDecision checkAndConsume(BucketState currentState, EffectiveRateLimit limit, long nowMillis) {

        final var state = resolveState(currentState, limit, nowMillis);
        final var refilled = refillTokens(state, limit, nowMillis);

        if (refilled.tokens() > 0) {
            return createAllowedResult(refilled, limit, nowMillis);
        }
        return createRejectedResult(refilled, limit, nowMillis);
    }

    public BucketState createInitialState(EffectiveRateLimit limit, long nowMillis) {
        return new BucketState(limit.burstCapacity(), nowMillis);
    }

    public RateLimitDecision computeStatus(BucketState currentState, EffectiveRateLimit limit, long nowMillis) {

        final var state = currentState;
        final var refilled = refillTokens(state, limit, nowMillis);
        return createStatusResult(refilled, limit, nowMillis);
    }

    public RateLimitDecision getStatus(BucketState currentState, EffectiveRateLimit limit, long nowMillis) {
        if (currentState == null) {
            final var initialState = createInitialState(limit, nowMillis);
            final var resetAt = computeResetTime(nowMillis, limit.windowSeconds());
            return RateLimitDecision.allow(
                    initialState.tokens(),
                    limit.requestsPerWindow(),
                    limit.windowSeconds(),
                    resetAt,
                    (int) (limit.requestsPerWindow() - initialState.tokens()),
                    initialState);
        }
        return computeStatus(currentState, limit, nowMillis);
    }

    private BucketState resolveState(BucketState currentState, EffectiveRateLimit limit, long nowMillis) {

        if (currentState == null) {
            return createInitialState(limit, nowMillis);
        }
        return currentState;
    }

    private BucketState refillTokens(BucketState state, EffectiveRateLimit limit, long nowMillis) {

        final var refillRate = limit.refillRatePerSecond();
        final var tokensToAdd = state.calculateRefillTokens(refillRate, nowMillis);

        if (tokensToAdd <= 0) {
            return state;
        }
        return state.refill(tokensToAdd, limit.burstCapacity(), nowMillis);
    }

    private RateLimitDecision createAllowedResult(BucketState state, EffectiveRateLimit limit, long nowMillis) {

        final var consumed = state.consume();
        final var resetAt = computeResetTime(nowMillis, limit.windowSeconds());
        final var requestCount = (int) (limit.burstCapacity() - consumed.tokens());

        return RateLimitDecision.allow(
                consumed.tokens(), limit.requestsPerWindow(), limit.windowSeconds(), resetAt, requestCount, consumed);
    }

    private RateLimitDecision createRejectedResult(BucketState state, EffectiveRateLimit limit, long nowMillis) {

        final var resetAt = computeResetTime(nowMillis, limit.windowSeconds());
        final var retryAfter = calculateRetryAfter(limit, nowMillis);
        final var requestCount = (int) limit.burstCapacity();

        return RateLimitDecision.rejected(
                limit.requestsPerWindow(), limit.windowSeconds(), resetAt, retryAfter, requestCount, state);
    }

    private RateLimitDecision createStatusResult(BucketState state, EffectiveRateLimit limit, long nowMillis) {

        final var resetAt = computeResetTime(nowMillis, limit.windowSeconds());
        final var requestCount = (int) (limit.burstCapacity() - state.tokens());

        return RateLimitDecision.allow(
                state.tokens(), limit.requestsPerWindow(), limit.windowSeconds(), resetAt, requestCount, state);
    }

    private long calculateRetryAfter(EffectiveRateLimit limit, long nowMillis) {
        final var refillRate = limit.refillRatePerSecond();
        if (refillRate <= 0) {
            return limit.windowSeconds();
        }
        // Time until one token is available
        final var secondsUntilToken = 1.0 / refillRate;
        return Math.max(1, (long) Math.ceil(secondsUntilToken));
    }

    public Instant computeResetTime(long nowMillis, long windowSeconds) {
        // For bucket algorithm, reset time is when bucket would be full again
        // This is an approximation based on window boundaries
        final var windowMillis = windowSeconds * 1000;
        final var windowStart = (nowMillis / windowMillis) * windowMillis;
        return Instant.ofEpochMilli(windowStart + windowMillis);
    }
}

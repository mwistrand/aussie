package aussie.core.model;

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
public final class BucketAlgorithm implements RateLimitAlgorithmHandler {

    private static final BucketAlgorithm INSTANCE = new BucketAlgorithm();

    private BucketAlgorithm() {}

    /**
     * Returns the singleton instance.
     *
     * @return the bucket algorithm instance
     */
    public static BucketAlgorithm getInstance() {
        return INSTANCE;
    }

    @Override
    public RateLimitAlgorithm algorithm() {
        return RateLimitAlgorithm.BUCKET;
    }

    @Override
    public RateLimitDecision checkAndConsume(RateLimitState currentState, EffectiveRateLimit limit, long nowMillis) {

        final var state = resolveState(currentState, limit, nowMillis);
        final var refilled = refillTokens(state, limit, nowMillis);

        if (refilled.tokens() > 0) {
            return createAllowedResult(refilled, limit, nowMillis);
        }
        return createRejectedResult(refilled, limit, nowMillis);
    }

    @Override
    public RateLimitState createInitialState(EffectiveRateLimit limit, long nowMillis) {
        return new BucketState(limit.burstCapacity(), nowMillis);
    }

    @Override
    public RateLimitDecision computeStatus(RateLimitState currentState, EffectiveRateLimit limit, long nowMillis) {

        final var state = (BucketState) currentState;
        final var refilled = refillTokens(state, limit, nowMillis);
        return createStatusResult(refilled, limit, nowMillis);
    }

    private BucketState resolveState(RateLimitState currentState, EffectiveRateLimit limit, long nowMillis) {

        if (currentState == null) {
            return (BucketState) createInitialState(limit, nowMillis);
        }
        return (BucketState) currentState;
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

    @Override
    public Instant computeResetTime(long nowMillis, long windowSeconds) {
        // For bucket algorithm, reset time is when bucket would be full again
        // This is an approximation based on window boundaries
        final var windowMillis = windowSeconds * 1000;
        final var windowStart = (nowMillis / windowMillis) * windowMillis;
        return Instant.ofEpochMilli(windowStart + windowMillis);
    }
}

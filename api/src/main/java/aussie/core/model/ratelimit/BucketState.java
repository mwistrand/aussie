package aussie.core.model.ratelimit;

/**
 * Token bucket state for rate limiting.
 *
 * <p>The token bucket algorithm maintains a bucket of tokens that refills at a steady
 * rate. Each request consumes one token. When the bucket is empty, requests are rejected.
 *
 * <p>This allows controlled bursts up to the bucket capacity while maintaining an
 * average rate over time.
 *
 * @param tokens the current number of tokens in the bucket
 * @param lastRefillMillis the timestamp of the last refill operation (epoch millis)
 */
public record BucketState(long tokens, long lastRefillMillis) implements RateLimitState {

    /**
     * Creates a bucket state with validation.
     */
    public BucketState {
        if (tokens < 0) {
            throw new IllegalArgumentException("tokens must be non-negative");
        }
        if (lastRefillMillis < 0) {
            throw new IllegalArgumentException("lastRefillMillis must be non-negative");
        }
    }

    /**
     * Creates an initial bucket state with full capacity.
     *
     * @param capacity the initial token count
     * @return the initial state
     */
    public static BucketState initial(long capacity) {
        return new BucketState(capacity, System.currentTimeMillis());
    }

    /**
     * Returns a new state after consuming one token.
     *
     * @return the new state with one fewer token
     * @throws IllegalStateException if no tokens are available
     */
    public BucketState consume() {
        if (tokens <= 0) {
            throw new IllegalStateException("No tokens available to consume");
        }
        return new BucketState(tokens - 1, lastRefillMillis);
    }

    /**
     * Returns a new state after refilling tokens.
     *
     * @param tokensToAdd the number of tokens to add
     * @param capacity the maximum capacity
     * @param now the current timestamp
     * @return the new state with refilled tokens
     */
    public BucketState refill(long tokensToAdd, long capacity, long now) {
        final var newTokens = Math.min(capacity, tokens + tokensToAdd);
        return new BucketState(newTokens, now);
    }

    /**
     * Calculates how many tokens to refill based on elapsed time.
     *
     * @param refillRatePerSecond tokens per second
     * @param nowMillis current time in milliseconds
     * @return number of tokens to refill
     */
    public long calculateRefillTokens(double refillRatePerSecond, long nowMillis) {
        final var elapsedMillis = nowMillis - lastRefillMillis;
        final var elapsedSeconds = elapsedMillis / 1000.0;
        return (long) (elapsedSeconds * refillRatePerSecond);
    }

    @Override
    public long remaining() {
        return tokens;
    }

    @Override
    public long timestampMillis() {
        return lastRefillMillis;
    }
}

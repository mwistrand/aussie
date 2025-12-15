package aussie.core.model.ratelimit;

/**
 * Algorithm-agnostic interface for rate limit state.
 *
 * <p>Different rate limiting algorithms maintain different state:
 * <ul>
 *   <li>Token bucket: current tokens, last refill timestamp</li>
 *   <li>Fixed window: request count, window start timestamp</li>
 *   <li>Sliding window: request counts for current and previous windows</li>
 * </ul>
 *
 * <p>Implementations must be immutable and safe for concurrent access.
 *
 * @see BucketState
 */
public sealed interface RateLimitState permits BucketState {

    /**
     * Returns the number of requests remaining in the current window.
     *
     * <p>For token bucket, this is the current token count.
     * For window-based algorithms, this is the limit minus current count.
     *
     * @return remaining requests
     */
    long remaining();

    /**
     * Returns the timestamp (epoch millis) for state calculations.
     *
     * <p>For token bucket, this is the last refill time.
     * For window-based algorithms, this is the window start time.
     *
     * @return timestamp in epoch milliseconds
     */
    long timestampMillis();
}

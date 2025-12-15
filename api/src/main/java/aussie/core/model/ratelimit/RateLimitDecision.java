package aussie.core.model.ratelimit;

import java.time.Instant;

/**
 * Result of a rate limit check, indicating whether a request is allowed and
 * providing information about the current rate limit state.
 *
 * @param allowed whether the request is allowed
 * @param remaining number of requests remaining in the current window
 * @param limit the total limit for the window
 * @param windowSeconds the window duration in seconds
 * @param resetAt when the rate limit window resets
 * @param retryAfterSeconds seconds until the client can retry (only meaningful when not allowed)
 * @param requestCount total requests made in the current window (for security events)
 * @param newState the updated rate limit state (for storage)
 */
public record RateLimitDecision(
        boolean allowed,
        long remaining,
        long limit,
        long windowSeconds,
        Instant resetAt,
        long retryAfterSeconds,
        int requestCount,
        RateLimitState newState) {

    /**
     * Create an "allowed" decision with default values.
     *
     * <p>Used when rate limiting is disabled or the check cannot be performed.
     *
     * @return an allowed decision
     */
    public static RateLimitDecision allow() {
        return new RateLimitDecision(true, Long.MAX_VALUE, Long.MAX_VALUE, 0, Instant.MAX, 0, 0, null);
    }

    /**
     * Create an "allowed" decision with the specified state.
     *
     * @param remaining remaining requests in the window
     * @param limit the total limit
     * @param windowSeconds the window duration
     * @param resetAt when the window resets
     * @param requestCount requests made in this window
     * @param newState the updated state
     * @return an allowed decision
     */
    public static RateLimitDecision allow(
            long remaining,
            long limit,
            long windowSeconds,
            Instant resetAt,
            int requestCount,
            RateLimitState newState) {
        return new RateLimitDecision(true, remaining, limit, windowSeconds, resetAt, 0, requestCount, newState);
    }

    /**
     * Create a "rejected" decision indicating rate limit exceeded.
     *
     * @param limit the total limit
     * @param windowSeconds the window duration
     * @param resetAt when the window resets
     * @param retryAfterSeconds seconds until retry is allowed
     * @param requestCount requests made in this window
     * @param newState the current state
     * @return a rejected decision
     */
    public static RateLimitDecision rejected(
            long limit,
            long windowSeconds,
            Instant resetAt,
            long retryAfterSeconds,
            int requestCount,
            RateLimitState newState) {
        return new RateLimitDecision(
                false, 0, limit, windowSeconds, resetAt, retryAfterSeconds, requestCount, newState);
    }

    /**
     * Return the reset time as epoch seconds for response headers.
     *
     * @return reset time as epoch seconds
     */
    public long resetAtEpochSeconds() {
        return resetAt.getEpochSecond();
    }
}

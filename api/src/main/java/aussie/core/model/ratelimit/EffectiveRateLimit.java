package aussie.core.model.ratelimit;

/**
 * The resolved rate limit configuration for a specific request.
 *
 * <p>This represents the effective rate limit after applying the configuration
 * hierarchy: platform maximum → service default → endpoint override.
 *
 * @param requestsPerWindow the maximum requests allowed per window
 * @param windowSeconds the duration of the rate limit window in seconds
 * @param burstCapacity the burst capacity (for token bucket algorithm)
 */
public record EffectiveRateLimit(long requestsPerWindow, long windowSeconds, long burstCapacity) {

    /**
     * Create an effective rate limit with validation.
     */
    public EffectiveRateLimit {
        if (requestsPerWindow < 0) {
            throw new IllegalArgumentException("requestsPerWindow must be non-negative");
        }
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("windowSeconds must be positive");
        }
        if (burstCapacity < 0) {
            throw new IllegalArgumentException("burstCapacity must be non-negative");
        }
    }

    /**
     * Create a disabled rate limit (unlimited).
     *
     * @return an unlimited rate limit
     */
    public static EffectiveRateLimit unlimited() {
        return new EffectiveRateLimit(Long.MAX_VALUE, 60, Long.MAX_VALUE);
    }

    /**
     * Create an effective rate limit with burst capacity equal to requests per window.
     *
     * @param requestsPerWindow the maximum requests per window
     * @param windowSeconds the window duration
     * @return the rate limit
     */
    public static EffectiveRateLimit of(long requestsPerWindow, long windowSeconds) {
        return new EffectiveRateLimit(requestsPerWindow, windowSeconds, requestsPerWindow);
    }

    /**
     * Return a new rate limit capped by the platform maximum.
     *
     * @param platformMax the platform maximum requests per window
     * @return a new rate limit with values capped at the platform maximum
     */
    public EffectiveRateLimit capAtPlatformMax(long platformMax) {
        if (requestsPerWindow <= platformMax && burstCapacity <= platformMax) {
            return this;
        }
        return new EffectiveRateLimit(
                Math.min(requestsPerWindow, platformMax), windowSeconds, Math.min(burstCapacity, platformMax));
    }

    /**
     * Calculate the refill rate for token bucket algorithm.
     *
     * @return tokens per second
     */
    public double refillRatePerSecond() {
        return (double) requestsPerWindow / windowSeconds;
    }
}
